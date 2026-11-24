package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * How long the view must rest before the pages are asked sharper: a pinch goes through many zooms, and only
 * the one it ends at, or stays at, is worth drawing a page for.
 */
internal const val SharpenRestMillis = 300L

/**
 * A page of the spread shown whose picture is on the device.
 *
 * @property index the page, from 0.
 * @property image the size of its picture, in pixels.
 * @property slot the size of its room at the fit, in pixels.
 */
internal data class SharpTarget(val index: Int, val image: IntSize, val slot: Size)

/** The highest zoom each page was asked sharper for, while what is shown stays the same. */
private class SharpAsked {
    private val zooms = mutableMapOf<Int, Float>()

    /**
     * Whether the page [index] was never asked, or for less than [zoom]; it is then what it was asked for.
     */
    fun more(index: Int, zoom: Float): Boolean {
        val more = zooms[index]?.let { zoom > it } ?: true
        if (more) zooms[index] = zoom
        return more
    }
}

/**
 * Asks, once the [zoom] rests, for each of the [targets] whose picture is smaller than what is shown of it to be
 * drawn sharper, with the long side the zoom needs. At the fit too: a page is first drawn before its room is
 * known, and a large screen shows it larger than that. A page is asked once, and again only for a higher zoom.
 * The pages of another spread, shown in their turn, are asked in theirs.
 *
 * @param scene what the pages are shown in: when it changes (another spread, another window, another fit), what
 *   was asked before no longer counts.
 */
@Composable
internal fun SharpenAtRest(
    zoom: SpreadZoom,
    scene: Any,
    targets: List<SharpTarget>,
    onSharpen: (index: Int, longSidePx: Int) -> Unit,
) {
    val asked = remember(scene) { SharpAsked() }
    val ask by rememberUpdatedState(onSharpen)
    LaunchedEffect(zoom, asked, targets) {
        snapshotFlow { zoom.scale }.collectLatest { scale ->
            delay(SharpenRestMillis)
            targets.forEach { page ->
                val side = sharpSide(page.image, page.slot, scale)
                if (tooSmallFor(page.image, side) && asked.more(page.index, scale)) ask(page.index, side)
            }
        }
    }
}
