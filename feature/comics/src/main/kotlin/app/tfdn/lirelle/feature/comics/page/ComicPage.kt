package app.tfdn.lirelle.feature.comics.page

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.pageNumberOf
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.LocalLirelleImageLoader
import app.tfdn.lirelle.core.ui.libraryMessage
import app.tfdn.lirelle.feature.comics.R
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.addLastModifiedToFileCacheKey
import coil3.request.crossfade

/** The mark of a page that is loading. */
const val ComicPageLoadingTag = "comic-page-loading"

/** The button that asks again for a page that could not be loaded. */
const val ComicPageRetryTag = "comic-page-retry"

private const val MarkAlpha = 0.6f

/**
 * What is common to the pages of a spread.
 *
 * @property turning the actions a screen reader offers on a page, to turn it without a gesture.
 */
internal class PageSetting(val colours: SpreadColours, val turning: List<CustomAccessibilityAction>)

/**
 * One page of the spread, in the room [modifier] gives it: its image, a mark while it loads, or what went wrong.
 *
 * @param index the page, from 0.
 * @param slot the size of that room at the fit, in pixels: the image is decoded for it.
 */
@Composable
internal fun ComicPage(
    index: Int,
    page: PageState,
    slot: Size,
    setting: PageSetting,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(R.string.comics_page, pageNumberOf(index))
    when (page) {
        is PageState.Ready -> PageImage(index, page, PageRoom(slot, name, setting.turning), modifier)
        PageState.Loading -> Box(
            modifier.semantics {
                contentDescription = name
                customActions = setting.turning
            },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp).testTag(ComicPageLoadingTag),
                color = setting.colours.content.copy(alpha = MarkAlpha),
                strokeWidth = 2.dp,
            )
        }
        is PageState.Failed -> PageFailure(pageNumberOf(index), page.error, setting, onRetry, modifier)
    }
}

/**
 * Where the picture of a page is drawn.
 *
 * @property slot the size of the room at the fit, in pixels.
 * @property name what a screen reader says of the page.
 * @property turning the actions a screen reader offers on it.
 */
private class PageRoom(val slot: Size, val name: String, val turning: List<CustomAccessibilityAction>)

/**
 * The picture of the page [index]. When another one takes its place (the page drawn sharper for the zoom), the
 * one shown stays under it until the new one is decoded: the page is never blank in between.
 *
 * The picture under is the very one that was shown, in the place it had here, and asks nothing of the image
 * loader again: its file is removed as soon as the sharper picture is told, and what was decoded of it is all
 * there is.
 */
@Composable
private fun PageImage(index: Int, page: PageState.Ready, room: PageRoom, modifier: Modifier) {
    var drawn by remember(index) { mutableStateOf(page) }
    Box(modifier) {
        val under = drawn.takeIf { it.file != page.file }
        listOfNotNull(under, page).forEach { picture ->
            // One place for both, told apart by their files: a picture stays what it is when it goes under.
            key(picture.file) {
                val shown = picture.file == page.file
                Picture(
                    picture = picture,
                    slot = room.slot,
                    name = room.name.takeIf { shown },
                    modifier = when {
                        shown -> Modifier.matchParentSize().semantics { customActions = room.turning }
                        else -> Modifier.matchParentSize()
                    },
                    onDrawn = { drawn = picture },
                )
            }
        }
    }
}

/**
 * A picture, decoded from its file no larger than the highest zoom shows it; [onDrawn] once it is decoded. It
 * is there at once, without the fade the app's covers come with: a page turned to is read, and a sharper
 * picture fading in over the one it replaces would show the background through both.
 *
 * The image loader remembers a decoded picture under its file and the time that file was written, not under
 * its file alone, as it does unless told: a page the downloads keep has the same file before and after an
 * update of its album, and the picture remembered of the older one would otherwise be drawn for the newer.
 */
@Composable
private fun Picture(
    picture: PageState.Ready,
    slot: Size,
    name: String?,
    modifier: Modifier,
    onDrawn: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val size = decodeSize(IntSize(picture.width, picture.height), slot)
    val request = remember(context, picture.file, size) {
        ImageRequest.Builder(context)
            .data(picture.file)
            .size(size.width, size.height)
            .addLastModifiedToFileCacheKey(true)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = name,
        imageLoader = LocalLirelleImageLoader.current ?: SingletonImageLoader.get(context),
        modifier = modifier,
        onSuccess = { onDrawn() },
        // The room has the shape of the image.
        contentScale = ContentScale.FillBounds,
    )
}

/**
 * In place of a page that could not be loaded: which one, why, and a way to ask again. It scrolls when short of
 * room.
 */
@Composable
private fun PageFailure(
    number: Int,
    error: ApiError,
    setting: PageSetting,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val colours = setting.colours
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.comics_page_failed, number),
            modifier = Modifier.semantics { customActions = setting.turning },
            color = colours.content,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = error.libraryMessage(),
            color = colours.content,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        // Outlined: a filled black or white button is harsh on the reader's plain backgrounds.
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp).testTag(ComicPageRetryTag),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colours.content),
            border = BorderStroke(1.dp, colours.content),
        ) {
            Text(stringResource(R.string.comics_page_retry))
        }
    }
}
