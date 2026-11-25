package app.tfdn.lirelle.feature.comics.strip

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.pageNumberOf
import app.tfdn.lirelle.feature.comics.R
import app.tfdn.lirelle.feature.comics.page.ComicPage
import app.tfdn.lirelle.feature.comics.page.PageSetting

/** The first item of the page N, from 0: "comic-strip-page-N". */
fun comicStripPageTag(index: Int) = "comic-strip-page-$index"

/** A slice of a tall page, once it is decoded and drawn. */
const val StripSliceTag = "comic-strip-slice"

/**
 * One item of the strip.
 *
 * @property index its page, from 0.
 * @property slice which slice of the page it is, from 0; 0 for a page drawn whole.
 * @property room the room of its page.
 * @property width how wide the strip is, in pixels.
 */
internal data class StripItem(val index: Int, val slice: Int, val room: StripRoom, val width: Int)

/**
 * What an item of the strip is drawn with.
 *
 * @property onRetry asks again for a page that could not be loaded.
 * @property onUnreadable the picture of a page could not be read in parts.
 */
internal class StripDrawing(
    val setting: PageSetting,
    val decoders: SliceDecoders,
    val onRetry: (Int) -> Unit,
    val onUnreadable: (Int) -> Unit,
)

/**
 * An [item] of the strip, as wide as the strip and as high as its room says: the list never measures a picture.
 * A page that fits one picture, or that is not there, is drawn as the pages that are turned are: its picture,
 * the loading mark, or what went wrong. A page too tall for one picture is one item for each of its slices; the
 * first one is found by [comicStripPageTag], as a page drawn whole is.
 */
@Composable
internal fun StripPage(item: StripItem, page: PageState, drawing: StripDrawing) {
    val room = item.room
    val height = room.heightOf(item.slice)
    val place = Modifier.sized(item.width, height).let {
        if (item.slice == 0) it.testTag(comicStripPageTag(item.index)) else it
    }
    val slice = room.slices.getOrNull(item.slice)
    if (page is PageState.Ready && slice != null) {
        StripSliceImage(
            picture = SlicePicture(page, slice, room.sample),
            decoders = drawing.decoders,
            // The page is named once, by its first slice.
            name = stringResource(R.string.comics_page, pageNumberOf(item.index)).takeIf { item.slice == 0 },
            onUnreadable = { drawing.onUnreadable(item.index) },
            modifier = place,
        )
    } else {
        ComicPage(
            index = item.index,
            page = page,
            slot = Size(item.width.toFloat(), height.toFloat()),
            setting = drawing.setting,
            onRetry = { drawing.onRetry(item.index) },
            modifier = place,
        )
    }
}

/** The rows [slice] of the picture of [page], decoded at [sample]. */
internal data class SlicePicture(val page: PageState.Ready, val slice: StripSlice, val sample: Int)

/**
 * A slice of a tall page, decoded from the page's file and stretched over the room [modifier] gives it, which
 * has its shape; nothing but the background until it is decoded. [onUnreadable] when it cannot be.
 *
 * The decoded slice is held by this item alone, for as long as the list keeps it composed: the list lets go of
 * the items that leave the screen, and their slices with them, so that only the slices near the screen are in
 * memory. A slice still being decoded when its item goes is given up.
 */
@Composable
private fun StripSliceImage(
    picture: SlicePicture,
    decoders: SliceDecoders,
    name: String?,
    onUnreadable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unreadable by rememberUpdatedState(onUnreadable)
    val decoded by produceState<ImageBitmap?>(null, decoders, picture) {
        // Another picture, or other rows of it: what was drawn is not shown in their place.
        value = null
        val bitmap = decoders.decode(picture.page.file, picture.slice, picture.sample)
        if (bitmap == null) unreadable() else value = bitmap.asImageBitmap()
    }
    Box(if (name == null) modifier else modifier.semantics { contentDescription = name }) {
        decoded?.let {
            Image(
                bitmap = it,
                // The room says which page it is, decoded or not.
                contentDescription = null,
                modifier = Modifier.matchParentSize().testTag(StripSliceTag),
                // The room has the shape of the slice.
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Exactly [width] by [height] pixels, whatever the content: heights in pixels add up, heights in dp do not. */
private fun Modifier.sized(width: Int, height: Int): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
